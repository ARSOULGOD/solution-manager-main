import { describe, expect, it } from 'vitest';

import { PERMISSION_CATALOG } from './catalog';
import { createPermissionApi } from './permissions';

describe('createPermissionApi', () => {
  it('has() reflects exactly the granted ids', () => {
    const api = createPermissionApi(['target.use'], PERMISSION_CATALOG);

    expect(api.has('target.use')).toBe(true);
    expect(api.has('instance.bindTarget')).toBe(false);
  });

  it('status() reads the catalog regardless of what is granted', () => {
    const api = createPermissionApi([], PERMISSION_CATALOG);

    expect(api.status('target.use')).toBe('grantable');
    expect(api.status('metrics.query')).toBe('grantable');
    expect(api.status('instance.restart')).toBe('grantable');
  });

  it('status() reports "absent" for an id not in the catalog at all', () => {
    const api = createPermissionApi([], PERMISSION_CATALOG);
    expect(api.status('not.a.real.permission')).toBe('absent');
  });

  it('gate() is "omit" for an ungranted id, "render" for a granted one — G13, never confused', () => {
    const api = createPermissionApi(['target.use'], PERMISSION_CATALOG);

    expect(api.gate('target.use')).toBe('render');
    expect(api.gate('instance.bindTarget')).toBe('omit');
  });

  it('gate() omits even a `grantable`-status id that simply was not granted to THIS identity', () => {
    const api = createPermissionApi([], PERMISSION_CATALOG);
    expect(api.gate('target.use')).toBe('omit');
  });
});
