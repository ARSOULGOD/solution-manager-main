import { describe, expect, it } from 'vitest';

import { DESCRIPTORS } from '@shared/testdoubles/data/connections';

import { validateConnection, visibleAttributes } from './descriptorForm';

const REMOTE = DESCRIPTORS['remote-server'];

describe('visibleAttributes', () => {
  it('shows password for basic auth and hides the key fields', () => {
    const keys = visibleAttributes(REMOTE, { authMethod: 'basic' }).map((a) => a.key);
    expect(keys).toContain('password');
    expect(keys).not.toContain('privateKey');
    expect(keys).not.toContain('passphrase');
  });

  it('shows the key fields for private-key auth and hides password', () => {
    const keys = visibleAttributes(REMOTE, { authMethod: 'private-key' }).map((a) => a.key);
    expect(keys).toEqual(expect.arrayContaining(['privateKey', 'passphrase']));
    expect(keys).not.toContain('password');
  });

  it('is driven purely by the descriptor - no auth-method knowledge in code', () => {
    // An unknown discriminator value hides every predicated attribute
    // rather than falling back to a hardcoded default.
    const keys = visibleAttributes(REMOTE, { authMethod: 'kerberos' }).map((a) => a.key);
    expect(keys).not.toContain('password');
    expect(keys).not.toContain('privateKey');
  });
});

describe('validateConnection', () => {
  it('requires a required attribute', () => {
    const errors = validateConnection(REMOTE, { authMethod: 'basic', port: 22 }, {});
    expect(errors.host).toBeDefined();
    expect(errors.username).toBeDefined();
  });

  it('requires a secret only when it is unset AND visible', () => {
    const missing = validateConnection(REMOTE,
      { host: 'h', username: 'u', authMethod: 'private-key', port: 22 }, {});
    expect(missing.privateKey).toBeDefined();
    expect(missing.passphrase).toBeUndefined(); // optional

    const alreadySet = validateConnection(REMOTE,
      { host: 'h', username: 'u', authMethod: 'private-key', port: 22 }, { privateKey: 'set' });
    expect(alreadySet.privateKey).toBeUndefined();
  });

  it('enforces the declared numeric range', () => {
    const errors = validateConnection(REMOTE,
      { host: 'h', username: 'u', authMethod: 'basic', port: 70000 }, { password: 'set' });
    expect(errors.port).toBeDefined();
  });
});
