/**
 * The declare form's rules, asserted without rendering a dialog.
 *
 * Rewritten for integration plan 007 (2026-10-09): `role`/`componentTypes`,
 * `driver`/`locus`, the replica-name array, and `connections` are all gone
 * — see `declareForm.ts`'s header for why none of it maps to the real
 * `declareInstance` body. This suite now covers: name/package-version/
 * cascade validation, the binding/placement/acquisition fields the real
 * body requires, and `toDeclareInput`'s real shape.
 *
 * Integration plan 014 (2026-10-10): the binding is a remote-server
 * Connection (`connectionId`) under `deploymentMode: dp-remote`, replacing
 * plan 007's `connectionId` on this form.
 */
import { describe, expect, it } from 'vitest';

import type { Instance } from '@shared/api/contracts/instances';

import type { RemoteServerConnectionRow } from './connectionPickerRepository';
import {
  DEPLOYMENT_MODES,
  hasErrors,
  initialDeclareForm,
  setDeploymentMode,
  setEnv,
  setOrg,
  setPackageVersion,
  setSubOrg,
  setTarget,
  suggestInstanceName,
  targetPlacementDefaults,
  toDeclareInput,
  validateDeclareForm,
  type DeclareFormState,
} from './declareForm';

const MESSAGES = {
  required: 'required',
  nameFormat: 'format',
  nameTaken: 'taken',
  packageVersionUnknown: 'unknown-version',
  healthPortRange: 'port-range',
  deploymentModeUnavailable: 'mode-unavailable',
};

/** What the Cache package declares (FE-118) — a number-valued, two-entry list. */
const VERSIONS: readonly (string | number)[] = [6, 7];

const CONNECTION: RemoteServerConnectionRow = {
  connectionId: 'conn-1',
  name: 'build-host',
  connectionType: 'remote-server',
  attributes: { allowedConfigRoot: '/etc', allowedContainerPrefix: 'cache-' },
  active: true,
};
const CONNECTIONS: readonly RemoteServerConnectionRow[] = [CONNECTION];

function existingInstance(name: string): Instance {
  return {
    stateVersion: 1,
    instanceId: name,
    name,
    solutionId: 'cache',
    environmentId: 'env-1',
    declaredPackageVersion: 7,
    acquisition: { mode: 'direct-http' },
    placements: [],
  };
}

function filled(overrides: Partial<DeclareFormState> = {}): DeclareFormState {
  return {
    ...initialDeclareForm('cache', [], VERSIONS),
    org: 'Acme Financial',
    subOrg: 'Payments',
    // Nothing below is defaulted (`FE-D46`) — the cascade cannot know a
    // valid environment until org and sub-org are chosen — so a filled
    // form is one where the operator has chosen each level.
    env: 'PROD',
    // Two versions are offered, so nothing is preselected — a filled form
    // is one where the operator has chosen.
    packageVersion: '7',
    connectionId: 'conn-1',
    containerName: 'cache-prod-01',
    configDirectory: '/etc/cache',
    containerConfigPath: '/config',
    fileName: 'application.yml',
    healthPort: '8080',
    acquisitionMode: 'direct-http',
    ...overrides,
  };
}

describe('suggestInstanceName', () => {
  it('takes the first free ordinal rather than one that already exists', () => {
    expect(suggestInstanceName('cache', [])).toBe('cache-01');
    expect(suggestInstanceName('cache', ['cache-01', 'cache-02'])).toBe('cache-03');
  });

  it('ignores case when deciding a name is taken', () => {
    expect(suggestInstanceName('cache', ['CACHE-01'])).toBe('cache-02');
  });

  it('slugifies a solution id that is not already a valid name', () => {
    expect(suggestInstanceName('DB Designer', [])).toBe('db-designer-01');
  });
});

describe('initialDeclareForm', () => {
  // Was "defaults to DEV". Under the cascade (`FE-D46`) an environment
  // cannot be defaulted at all — the valid set is unknown until org and
  // sub-org are chosen — so the guarantee is stronger, not weaker: a
  // one-click default cannot declare into ANY environment, prod included.
  it('leaves the environment unchosen — there is no valid default before the cascade runs', () => {
    expect(initialDeclareForm('cache', []).env).toBe('');
  });

  it('does not collide with an instance already declared', () => {
    const existing = [existingInstance('cache-01')];
    expect(initialDeclareForm('cache', existing).name).toBe('cache-02');
  });

  it('leaves the connection unchosen', () => {
    expect(initialDeclareForm('cache', []).connectionId).toBe('');
  });

  it('preselects dp-remote — the only deployment mode selectable today', () => {
    expect(initialDeclareForm('cache', []).deploymentMode).toBe('dp-remote');
  });

  it('preselects the package version when the package serves exactly one', () => {
    expect(initialDeclareForm('db-designer', [], [1]).packageVersion).toBe('1');
    expect(initialDeclareForm('cache-nightly', [], ['dev-2026.08.17']).packageVersion).toBe(
      'dev-2026.08.17',
    );
  });

  it('preselects NOTHING when the package serves several — no silent default to latest', () => {
    expect(initialDeclareForm('cache', [], [6, 7]).packageVersion).toBe('');
  });

  it('preselects nothing when the version list is not known yet', () => {
    expect(initialDeclareForm('cache', []).packageVersion).toBe('');
  });
});

describe('deployment-target placement defaults', () => {
  const dockerTarget = {
    targetId: 'docker-1',
    name: 'Docker host',
    kind: 'docker' as const,
    transport: 'ssh-docker' as const,
    runtime: 'docker' as const,
    ownershipScope: {},
    allowedConfigRoot: '/opt/file-mesh',
    allowedContainerPrefix: 'fm-',
  };
  const systemdTarget = {
    targetId: 'systemd-1',
    name: 'systemd host',
    kind: 'systemd' as const,
    transport: 'ssh' as const,
    runtime: 'systemd' as const,
    ownershipScope: {},
    runtimeSettingsJson: JSON.stringify({ configRoot: '/etc/file-mesh', serviceNamePattern: 'file-mesh-' }),
  };

  it('reads Docker config root and container prefix from the selected target', () => {
    expect(targetPlacementDefaults(dockerTarget)).toEqual({
      configDirectory: '/opt/file-mesh',
      containerName: 'fm-',
    });
  });

  it('uses runtime settings as a compatibility fallback', () => {
    expect(targetPlacementDefaults(systemdTarget)).toEqual({
      configDirectory: '/etc/file-mesh',
      containerName: 'file-mesh-',
    });
  });

  it('replaces both target-owned values when the target changes', () => {
    const state = initialDeclareForm('cache', [], [1]);
    const selected = setTarget(state, 'docker-1', [dockerTarget, systemdTarget]);
    expect(selected.targetId).toBe('docker-1');
    expect(selected.configDirectory).toBe('/opt/file-mesh');
    expect(selected.containerName).toBe('fm-');

    const changed = setTarget(selected, 'systemd-1', [dockerTarget, systemdTarget]);
    expect(changed.targetId).toBe('systemd-1');
    expect(changed.configDirectory).toBe('/etc/file-mesh');
    expect(changed.containerName).toBe('file-mesh-');
  });
});

describe('validateDeclareForm', () => {
  it('accepts a fully filled form', () => {
    expect(hasErrors(validateDeclareForm(filled(), [], MESSAGES, VERSIONS, CONNECTIONS))).toBe(false);
  });

  it('requires a name and the top of the cascade', () => {
    const errors = validateDeclareForm(
      filled({ name: '', org: '  ', subOrg: '' }),
      [],
      MESSAGES,
      VERSIONS,
      CONNECTIONS,
    );
    expect(errors.name).toBe('required');
    expect(errors.org).toBe('required');
  });

  // Flagging every empty level at once names the wrong field: with no
  // organization chosen there is no sub-org list to have failed to pick
  // from. One error at a time, at the highest unchosen level.
  it('flags only the highest unchosen level of the cascade', () => {
    const noOrg = validateDeclareForm(
      filled({ org: '', subOrg: '', env: '', connectionId: '' }),
      [],
      MESSAGES,
      VERSIONS,
      CONNECTIONS,
    );
    expect(noOrg.org).toBe('required');
    expect(noOrg.subOrg).toBeUndefined();
    expect(noOrg.env).toBeUndefined();
    expect(noOrg.connectionId).toBeUndefined();

    const noSubOrg = validateDeclareForm(
      filled({ subOrg: '', env: '', connectionId: '' }),
      [],
      MESSAGES,
      VERSIONS,
      CONNECTIONS,
    );
    expect(noSubOrg.org).toBeUndefined();
    expect(noSubOrg.subOrg).toBe('required');
    expect(noSubOrg.env).toBeUndefined();

    const noEnv = validateDeclareForm(
      filled({ env: '', connectionId: '' }),
      [],
      MESSAGES,
      VERSIONS,
      CONNECTIONS,
    );
    expect(noEnv.subOrg).toBeUndefined();
    expect(noEnv.env).toBe('required');
    expect(noEnv.connectionId).toBeUndefined();

    const noConnection = validateDeclareForm(filled({ connectionId: '' }), [], MESSAGES, VERSIONS, CONNECTIONS);
    expect(noConnection.env).toBeUndefined();
    expect(noConnection.connectionId).toBe('required');
  });

  it('requires an environment — there is no default to fall back on', () => {
    expect(initialDeclareForm('cache', [], VERSIONS).env).toBe('');
    expect(
      hasErrors(validateDeclareForm(filled({ env: '', connectionId: '' }), [], MESSAGES, VERSIONS, CONNECTIONS)),
    ).toBe(true);
  });

  it.each(['Cache-01', 'cache_01', '-cache', 'cache-', 'cache 01', 'cache.01'])(
    'rejects %s as an instance name',
    (name) => {
      expect(validateDeclareForm(filled({ name }), [], MESSAGES, VERSIONS, CONNECTIONS).name).toBe(
        'format',
      );
    },
  );

  it('rejects a name already declared, ignoring case', () => {
    expect(
      validateDeclareForm(filled({ name: 'cache-01' }), ['CACHE-01'], MESSAGES, VERSIONS, CONNECTIONS)
        .name,
    ).toBe('taken');
  });

  it('requires a package version to have been chosen', () => {
    const errors = validateDeclareForm(
      filled({ packageVersion: '' }),
      [],
      MESSAGES,
      VERSIONS,
      CONNECTIONS,
    );
    expect(errors.packageVersion).toBe('required');
    expect(hasErrors(errors)).toBe(true);
  });

  it('rejects a version this package does not serve — distinctly from "not chosen"', () => {
    const errors = validateDeclareForm(
      filled({ packageVersion: '9' }),
      [],
      MESSAGES,
      VERSIONS,
      CONNECTIONS,
    );
    expect(errors.packageVersion).toBe('unknown-version');
  });

  it('rejects a connection not in the known list — distinctly from "not chosen"', () => {
    const errors = validateDeclareForm(
      filled({ connectionId: 'not-a-real-connection' }),
      [],
      MESSAGES,
      VERSIONS,
      CONNECTIONS,
    );
    expect(errors.connectionId).toBe('required');
  });

  it.each(['containerName', 'configDirectory', 'containerConfigPath', 'fileName'] as const)(
    'requires %s',
    (field) => {
      const errors = validateDeclareForm(
        filled({ [field]: '' }),
        [],
        MESSAGES,
        VERSIONS,
        CONNECTIONS,
      );
      expect(errors[field]).toBe('required');
    },
  );

  it('requires a health port between 1 and 65535', () => {
    expect(
      validateDeclareForm(filled({ healthPort: '' }), [], MESSAGES, VERSIONS, CONNECTIONS).healthPort,
    ).toBe('required');
    expect(
      validateDeclareForm(filled({ healthPort: '0' }), [], MESSAGES, VERSIONS, CONNECTIONS).healthPort,
    ).toBe('port-range');
    expect(
      validateDeclareForm(filled({ healthPort: '70000' }), [], MESSAGES, VERSIONS, CONNECTIONS)
        .healthPort,
    ).toBe('port-range');
    expect(
      validateDeclareForm(filled({ healthPort: '8080' }), [], MESSAGES, VERSIONS, CONNECTIONS)
        .healthPort,
    ).toBeUndefined();
  });

  it('refuses a reserved deployment mode even if state somehow carries one', () => {
    for (const deploymentMode of ['host-local', 'federated'] as const) {
      const errors = validateDeclareForm(filled({ deploymentMode }), [], MESSAGES, VERSIONS, CONNECTIONS);
      expect(errors.deploymentMode).toBe('mode-unavailable');
    }
    expect(validateDeclareForm(filled(), [], MESSAGES, VERSIONS, CONNECTIONS).deploymentMode).toBeUndefined();
  });

  it('requires an acquisition mode — no default (M-Q2)', () => {
    expect(
      validateDeclareForm(filled({ acquisitionMode: '' }), [], MESSAGES, VERSIONS, CONNECTIONS)
        .acquisitionMode,
    ).toBe('required');
  });
});

describe('toDeclareInput', () => {
  it('trims every value and nests the placement envelope', () => {
    const input = toDeclareInput(
      filled({ name: ' cache-01 ', containerName: ' cache-01 ' }),
      'cache',
      VERSIONS,
    );
    expect(input.name).toBe('cache-01');
    expect(input.placement.containerName).toBe('cache-01');
  });

  it('maps the chosen version back to the id the package declared — a number stays a number', () => {
    expect(toDeclareInput(filled({ packageVersion: '6' }), 'cache', VERSIONS).declaredPackageVersion).toBe(
      6,
    );
    expect(
      toDeclareInput(filled({ packageVersion: 'dev-2026.08.17' }), 'cache', ['dev-2026.08.17'])
        .declaredPackageVersion,
    ).toBe('dev-2026.08.17');
  });

  it('carries the solutionId, connectionId, deploymentMode and acquisition mode exactly as the contract requires', () => {
    const input = toDeclareInput(filled(), 'cache', VERSIONS);
    expect(input.solutionId).toBe('cache');
    expect(input.connectionId).toBe('conn-1');
    expect(input.deploymentMode).toBe('dp-remote');
    expect(input.acquisition).toEqual({ mode: 'direct-http' });
  });

  it('carries no observed fact and no fixture-era field — those don’t exist on the real body', () => {
    const input = toDeclareInput(filled(), 'cache', VERSIONS);
    // Exactly one binding (`console.yaml`, plan 014): never `targetId` beside `connectionId`.
    for (const key of ['targetId', 'env', 'org', 'subOrg', 'driver', 'locus', 'replicas', 'componentTypes', 'connections', 'declaredVersion', 'role']) {
      expect(input).not.toHaveProperty(key);
    }
  });
});

describe('the dependent cascade (FE-D46)', () => {
  it('clears sub-org, environment AND connection when the organization changes', () => {
    const next = setOrg(filled(), 'Globex');

    expect(next.org).toBe('Globex');
    // 'Payments' does not exist under Globex; carrying it over is how a
    // form submits a triple that does not exist.
    expect(next.subOrg).toBe('');
    expect(next.env).toBe('');
    expect(next.connectionId).toBe('');
  });

  it('clears the environment and connection when the sub-org changes', () => {
    const next = setSubOrg(filled(), 'Trading');

    expect(next.subOrg).toBe('Trading');
    expect(next.env).toBe('');
    expect(next.connectionId).toBe('');
    // Level one is above the change and must survive it.
    expect(next.org).toBe('Acme Financial');
  });

  it('is a no-op when the same value is reselected, so a stray change event clears nothing', () => {
    const state = filled();

    expect(setOrg(state, 'Acme Financial')).toBe(state);
    expect(setSubOrg(state, 'Payments')).toBe(state);
  });

  it('clears the connection when the environment changes, and keeps everything above it', () => {
    const next = setEnv(filled(), 'TEST');
    expect(next.env).toBe('TEST');
    expect(next.connectionId).toBe('');
    expect(next.subOrg).toBe('Payments');
    expect(setEnv(filled(), 'PROD').connectionId).toBe('conn-1');
  });

  it('setPackageVersion only changes the version field', () => {
    const next = setPackageVersion(filled(), '6');
    expect(next.packageVersion).toBe('6');
    expect(next.connectionId).toBe(filled().connectionId);
  });
});

describe('deployment mode (integration plan 014)', () => {
  it('lists all three members of the vocabulary, with only dp-remote selectable', () => {
    expect(DEPLOYMENT_MODES.map((mode) => mode.value)).toEqual(['dp-remote', 'host-local', 'federated']);
    expect(DEPLOYMENT_MODES.filter((mode) => mode.selectable).map((mode) => mode.value)).toEqual([
      'dp-remote',
    ]);
  });

  it('ignores an attempt to switch to a reserved mode', () => {
    const state = filled();
    expect(setDeploymentMode(state, 'host-local')).toBe(state);
    expect(setDeploymentMode(state, 'federated')).toBe(state);
    expect(setDeploymentMode(state, 'dp-remote')).toBe(state);
  });
});
