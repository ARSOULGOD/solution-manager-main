/**
 * The declare form's rules, asserted without rendering a dialog.
 */
import { describe, expect, it } from 'vitest';

import { CACHE_INSTANCES, type InstanceRow } from '@shared/testdoubles/data/instances';

import {
  addReplica,
  hasErrors,
  initialDeclareForm,
  removeReplica,
  renameInstance,
  setOrg,
  setReplica,
  setSubOrg,
  suggestInstanceName,
  toDeclareInput,
  validateDeclareForm,
  type DeclareFormState,
} from './declareForm';

const MESSAGES = {
  required: 'required',
  nameFormat: 'format',
  nameTaken: 'taken',
  replicaDuplicate: 'duplicate',
  packageVersionUnknown: 'unknown-version',
};

/** What the Cache package declares (FE-118) — a number-valued, two-entry list. */
const VERSIONS: readonly (string | number)[] = [6, 7];

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
    const existing = [{ ...CACHE_INSTANCES[0], name: 'cache-01' } as InstanceRow];
    expect(initialDeclareForm('cache', existing).name).toBe('cache-02');
  });

  it('seeds one replica named after the instance', () => {
    expect(initialDeclareForm('cache', []).replicas).toEqual(['cache-01-0']);
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

describe('replica names following the instance name', () => {
  it('rewrites untouched replica defaults when the instance is renamed', () => {
    const renamed = renameInstance(initialDeclareForm('cache', []), 'ledger-eu');
    expect(renamed.replicas).toEqual(['ledger-eu-0']);
  });

  it('stops following once the operator edits a replica — an edit is never overwritten', () => {
    const edited = setReplica(initialDeclareForm('cache', []), 0, 'ledger-primary');
    const renamed = renameInstance(edited, 'ledger-eu');
    expect(renamed.replicas).toEqual(['ledger-primary']);
  });

  it('adds and removes replicas, but never removes the last one', () => {
    const two = addReplica(initialDeclareForm('cache', []));
    expect(two.replicas).toEqual(['cache-01-0', 'cache-01-1']);

    const one = removeReplica(two, 0);
    expect(one.replicas).toEqual(['cache-01-1']);
    expect(removeReplica(one, 0).replicas).toEqual(['cache-01-1']);
  });
});

describe('validateDeclareForm', () => {
  it('accepts a fully filled form', () => {
    expect(hasErrors(validateDeclareForm(filled(), [], MESSAGES, VERSIONS))).toBe(false);
  });

  it('requires a name and the top of the cascade', () => {
    const errors = validateDeclareForm(
      filled({ name: '', org: '  ', subOrg: '' }),
      [],
      MESSAGES,
      VERSIONS,
    );
    expect(errors.name).toBe('required');
    expect(errors.org).toBe('required');
  });

  // Flagging every empty level at once names the wrong field: with no
  // organization chosen there is no sub-org list to have failed to pick
  // from. One error at a time, at the highest unchosen level.
  it('flags only the highest unchosen level of the cascade', () => {
    const noOrg = validateDeclareForm(
      filled({ org: '', subOrg: '', env: '' }),
      [],
      MESSAGES,
      VERSIONS,
    );
    expect(noOrg.org).toBe('required');
    expect(noOrg.subOrg).toBeUndefined();
    expect(noOrg.env).toBeUndefined();

    const noSubOrg = validateDeclareForm(filled({ subOrg: '', env: '' }), [], MESSAGES, VERSIONS);
    expect(noSubOrg.org).toBeUndefined();
    expect(noSubOrg.subOrg).toBe('required');
    expect(noSubOrg.env).toBeUndefined();

    const noEnv = validateDeclareForm(filled({ env: '' }), [], MESSAGES, VERSIONS);
    expect(noEnv.subOrg).toBeUndefined();
    expect(noEnv.env).toBe('required');
  });

  it('requires an environment — there is no default to fall back on', () => {
    expect(initialDeclareForm('cache', [], VERSIONS).env).toBe('');
    expect(hasErrors(validateDeclareForm(filled({ env: '' }), [], MESSAGES, VERSIONS))).toBe(true);
  });

  it.each(['Cache-01', 'cache_01', '-cache', 'cache-', 'cache 01', 'cache.01'])(
    'rejects %s as an instance name',
    (name) => {
      expect(validateDeclareForm(filled({ name }), [], MESSAGES, VERSIONS).name).toBe('format');
    },
  );

  it('rejects a name already declared, ignoring case', () => {
    expect(
      validateDeclareForm(filled({ name: 'cache-01' }), ['CACHE-01'], MESSAGES, VERSIONS).name,
    ).toBe('taken');
  });

  it('flags a duplicate replica on the LATER occurrence, not the first', () => {
    const state = filled({ replicas: ['a', 'b', 'a'], replicasTouched: true });
    expect(validateDeclareForm(state, [], MESSAGES, VERSIONS).replicas).toEqual([
      undefined,
      undefined,
      'duplicate',
    ]);
  });

  it('requires every replica to be named', () => {
    const state = filled({ replicas: ['a', '  '], replicasTouched: true });
    expect(validateDeclareForm(state, [], MESSAGES, VERSIONS).replicas).toEqual([
      undefined,
      'required',
    ]);
  });

  it('requires a package version to have been chosen', () => {
    const errors = validateDeclareForm(filled({ packageVersion: '' }), [], MESSAGES, VERSIONS);
    expect(errors.packageVersion).toBe('required');
    expect(hasErrors(errors)).toBe(true);
  });

  it('rejects a version this package does not serve — distinctly from "not chosen"', () => {
    const errors = validateDeclareForm(filled({ packageVersion: '9' }), [], MESSAGES, VERSIONS);
    expect(errors.packageVersion).toBe('unknown-version');
  });
});

describe('toDeclareInput', () => {
  it('trims every value', () => {
    const input = toDeclareInput(
      filled({
        name: ' cache-01 ',
        org: ' Acme Financial ',
        replicas: [' r0 '],
        replicasTouched: true,
      }),
      VERSIONS,
    );
    expect(input).toMatchObject({ name: 'cache-01', org: 'Acme Financial', replicas: ['r0'] });
  });

  it('OMITS a blank declared version rather than sending an empty string', () => {
    expect(toDeclareInput(filled({ declaredVersion: '   ' }), VERSIONS)).not.toHaveProperty(
      'declaredVersion',
    );
    expect(toDeclareInput(filled({ declaredVersion: '2.3.0' }), VERSIONS)).toMatchObject({
      declaredVersion: '2.3.0',
    });
  });

  it('maps the chosen version back to the id the package declared — a number stays a number', () => {
    expect(toDeclareInput(filled({ packageVersion: '6' }), VERSIONS).packageVersion).toBe(6);
    expect(
      toDeclareInput(filled({ packageVersion: 'dev-2026.08.17' }), ['dev-2026.08.17'])
        .packageVersion,
    ).toBe('dev-2026.08.17');
  });

  it('carries no observed fact — those are reported, never declared', () => {
    const input = toDeclareInput(filled(), VERSIONS);
    for (const key of ['healthRollup', 'configState', 'lastSeen', 'version']) {
      expect(input).not.toHaveProperty(key);
    }
  });
});

describe('the dependent cascade (FE-D46)', () => {
  it('clears sub-org AND environment when the organization changes', () => {
    const next = setOrg(filled(), 'Globex');

    expect(next.org).toBe('Globex');
    // 'Payments' does not exist under Globex; carrying it over is how a
    // form submits a triple that does not exist.
    expect(next.subOrg).toBe('');
    expect(next.env).toBe('');
  });

  it('clears the environment when the sub-org changes', () => {
    const next = setSubOrg(filled(), 'Trading');

    expect(next.subOrg).toBe('Trading');
    expect(next.env).toBe('');
    // Level one is above the change and must survive it.
    expect(next.org).toBe('Acme Financial');
  });

  it('is a no-op when the same value is reselected, so a stray change event clears nothing', () => {
    const state = filled();

    expect(setOrg(state, 'Acme Financial')).toBe(state);
    expect(setSubOrg(state, 'Payments')).toBe(state);
  });
});
