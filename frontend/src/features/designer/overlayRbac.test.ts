import { describe, expect, it } from 'vitest';

import type { ViewModel } from '@contracts/view-model/types';
import { PERMISSION_CATALOG } from '@shared/authz/catalog';
import { createPermissionApi } from '@shared/authz/permissions';

import { overlayRbac } from './overlayRbac';

const VIEW_MODEL: ViewModel = {
  sections: [
    {
      title: 'General',
      order: 0,
      fields: [
        {
          key: 'mode',
          label: 'Mode',
          renderedPath: 'cache-engine.mode',
          discriminator: false,
          controlKind: 'select',
          value: { kind: 'value', value: 'READ', source: 'default' },
          options: ['READ', 'READ_WRITE'],
          enabled: true,
          visible: true,
          masked: false,
          advanced: false,
          validations: [],
        },
      ],
      actions: [],
    },
  ],
  graphs: [],
  status: { health: 'healthy', versionState: 'ok', configState: 'in_sync' },
};

describe('overlayRbac', () => {
  it('returns the view model unchanged when nothing is granted (v1: no grantable Designer verb exists)', () => {
    const permissions = createPermissionApi([], PERMISSION_CATALOG);

    expect(overlayRbac(VIEW_MODEL, permissions)).toEqual(VIEW_MODEL);
  });

  it('returns the view model unchanged even when every grantable verb is granted', () => {
    const permissions = createPermissionApi(
      ['target.use', 'instance.bindTarget'],
      PERMISSION_CATALOG,
    );

    expect(overlayRbac(VIEW_MODEL, permissions)).toEqual(VIEW_MODEL);
  });

  it('never mutates its input', () => {
    const frozen: ViewModel = JSON.parse(JSON.stringify(VIEW_MODEL)) as ViewModel;
    Object.freeze(frozen);
    Object.freeze(frozen.status);
    Object.freeze(frozen.sections);
    const permissions = createPermissionApi([], PERMISSION_CATALOG);

    expect(() => overlayRbac(frozen, permissions)).not.toThrow();
    expect(frozen).toEqual(VIEW_MODEL);
  });
});
