/**
 * The dependent scope picker — Organization → Sub-organization →
 * Environment → Instance (`D20`, `FE-D49`, `FE-D53`, Groups management §6).
 *
 * `ScopeTree` (task 092) remains the right control for *browsing* who
 * reaches what. It is the wrong control for *composing* a grant: a tree
 * lets an operator land on any node, so "an environment of the wrong
 * sub-organization" is a state the UI must then detect and reject. These
 * three selectors make it unrepresentable instead — each level's options
 * are the CHILDREN of the level above (`childNamesAt`), and changing a
 * parent clears every level below it.
 *
 * Organization is the top isolation/ownership level and the first segment
 * of every non-global scope path.
 *
 * `lockedOrganization` is how a group's own organization is enforced: a
 * group owned by Payments may never be granted inside Trading (§6), so the
 * organization is displayed, fixed, rather than offered and then refused.
 */
import type { ReactElement } from 'react';

import { t } from '@shared/i18n';
import type { ScopeNode } from '@shared/testdoubles/data/rbac';
import { Select, TextField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';

import { childNamesAt } from './scopePath';

/**
 * Which levels an assignment may be made at (`FE-D49`).
 *
 * ⚠️ **There is deliberately no root/global member.** `D19` 🔒 makes
 * root grants installer/database-managed in v1: the existing
 * `installer bootstrap` super-admin assignment at scope `[]` still
 * renders, still confers permissions through the prefix algebra, and is
 * still inspectable — but the console offers no flow that could create
 * another, and adding one here is what that would take.
 */
export type SelectableScopeType =
  | 'organization'
  | 'subOrganization'
  | 'environment'
  | 'instance';

export const SELECTABLE_SCOPE_TYPES: readonly SelectableScopeType[] = [
  'organization',
  'subOrganization',
  'environment',
  'instance',
];

export interface ScopeSelectorProps {
  scopeTree: ScopeNode;
  /** The scope path being composed, below the synthetic All root. */
  value: readonly string[];
  onChange: (next: string[]) => void;
  scopeType: SelectableScopeType;
  onScopeTypeChange: (next: SelectableScopeType) => void;
  /** When set, the organization is fixed to this and rendered read-only. */
  lockedOrganization?: string;
  disabled?: boolean;
}

const SCOPE_TYPE_LABEL: Record<SelectableScopeType, string> = {
  organization: t('adminScopeLevelOrganization'),
  subOrganization: t('adminScopeLevelSubOrganization'),
  environment: t('adminScopeLevelEnvironment'),
  instance: t('adminScopeLevelInstance'),
};

/** How many path segments a scope type needs before it is complete. */
export function segmentsRequired(scopeType: SelectableScopeType): number {
  switch (scopeType) {
    case 'organization':
      return 1;
    case 'subOrganization':
      return 2;
    case 'environment':
      return 3;
    case 'instance':
      return 4;
  }
}

/** True when `value` names a complete, valid scope of `scopeType`. */
export function isScopeComplete(value: readonly string[], scopeType: SelectableScopeType): boolean {
  return value.length === segmentsRequired(scopeType) && value.every((segment) => segment !== '');
}

export function ScopeSelector({
  scopeTree,
  value,
  onChange,
  scopeType,
  onScopeTypeChange,
  lockedOrganization,
  disabled = false,
}: ScopeSelectorProps): ReactElement {
  const organization = lockedOrganization ?? value[0] ?? '';
  const subOrganization = value[1] ?? '';
  const environment = value[2] ?? '';
  const instance = value[3] ?? '';

  const organizations = childNamesAt(scopeTree, []);
  const subOrganizations = organization === '' ? [] : childNamesAt(scopeTree, [organization]);
  const environments =
    organization === '' || subOrganization === ''
      ? []
      : childNamesAt(scopeTree, [organization, subOrganization]);
  const instances =
    organization === '' || subOrganization === '' || environment === ''
      ? []
      : childNamesAt(scopeTree, [organization, subOrganization, environment]);

  const depth = segmentsRequired(scopeType);

  function setOrganization(next: string): void {
    // Every lower level is cleared: a sub-organization of the previous
    // organization is not a sub-organization of this one.
    onChange(next === '' ? [] : [next]);
  }

  function setSubOrganization(next: string): void {
    if (organization === '') return;
    onChange(next === '' ? [organization] : [organization, next]);
  }

  function setEnvironment(next: string): void {
    if (organization === '' || subOrganization === '') return;
    // Clearing the instance is not optional: an instance of the previous
    // environment is not an instance of this one.
    onChange(next === '' ? [organization, subOrganization] : [organization, subOrganization, next]);
  }

  function setInstance(next: string): void {
    if (organization === '' || subOrganization === '' || environment === '') return;
    onChange(
      next === ''
        ? [organization, subOrganization, environment]
        : [organization, subOrganization, environment, next],
    );
  }

  return (
    <Stack gap={2}>
      <Select
        label={t('adminScopeTypeLabel')}
        helperText={t('adminScopeTypeHint')}
        value={scopeType}
        disabled={disabled}
        onChange={(next) => {
          const nextType = next as SelectableScopeType;
          onScopeTypeChange(nextType);
          // Narrowing the type trims the path; widening keeps what is
          // already chosen and asks for the rest.
          onChange(value.slice(0, segmentsRequired(nextType)));
        }}
        options={SELECTABLE_SCOPE_TYPES.map((type) => ({
          value: type,
          label: SCOPE_TYPE_LABEL[type],
        }))}
      />

      {lockedOrganization === undefined ? (
        <Select
          label={t('adminScopeOrganizationLabel')}
          required
          value={organization}
          disabled={disabled}
          onChange={setOrganization}
          options={organizations.map((name) => ({ value: name, label: name }))}
        />
      ) : (
        <TextField
          label={t('adminScopeOrganizationLabel')}
          value={lockedOrganization}
          readOnly
          onChange={() => undefined}
          helperText={t('adminScopeOrganizationLockedHint')}
        />
      )}

      {depth >= 2 && (
        <Select
          label={t('adminScopeSubOrganizationLabel')}
          required
          value={subOrganization}
          disabled={disabled || organization === ''}
          onChange={setSubOrganization}
          {...(organization === '' ? { helperText: t('adminScopePickOrganizationFirst') } : {})}
          options={subOrganizations.map((name) => ({ value: name, label: name }))}
        />
      )}

      {depth >= 3 && (
        <Select
          label={t('adminScopeEnvironmentLabel')}
          required
          value={environment}
          disabled={disabled || subOrganization === ''}
          onChange={setEnvironment}
          {...(subOrganization === ''
            ? { helperText: t('adminScopePickSubOrganizationFirst') }
            : {})}
          options={environments.map((name) => ({ value: name, label: name }))}
        />
      )}

      {depth >= 4 && (
        <Select
          label={t('adminScopeInstanceLabel')}
          required
          value={instance}
          disabled={disabled || environment === ''}
          onChange={setInstance}
          {...(environment === ''
            ? { helperText: t('adminScopePickEnvironmentFirst') }
            : instances.length === 0
              ? { helperText: t('adminScopeNoInstances') }
              : {})}
          options={instances.map((name) => ({ value: name, label: name }))}
        />
      )}
    </Stack>
  );
}
