import { describe, expect, it } from 'vitest';

import {
  ADMIN_TABS,
  INSTANCE_CATEGORIES,
  isAdminTab,
  isInstanceCategory,
  ROUTE_PATHS,
  routes,
} from './routes';

describe('isInstanceCategory', () => {
  it.each(INSTANCE_CATEGORIES)('accepts %s', (category) => {
    expect(isInstanceCategory(category)).toBe(true);
  });

  it('rejects garbage', () => {
    expect(isInstanceCategory('bogus')).toBe(false);
    expect(isInstanceCategory('')).toBe(false);
    expect(isInstanceCategory('Designer')).toBe(false);
  });
});

describe('isAdminTab', () => {
  it.each(ADMIN_TABS)('accepts %s', (tab) => {
    expect(isAdminTab(tab)).toBe(true);
  });

  it('rejects garbage', () => {
    expect(isAdminTab('bogus')).toBe(false);
    expect(isAdminTab('')).toBe(false);
    expect(isAdminTab('Roles')).toBe(false);
  });
});

describe('ROUTE_PATHS', () => {
  it('never carries a client-supplied organization-authority segment (I5, D20)', () => {
    for (const path of Object.values(ROUTE_PATHS)) {
      expect(path.toLowerCase()).not.toContain('organization');
    }
  });
});

describe('routes — typed link builders', () => {
  it('builds every path with no client-supplied organization-authority segment', () => {
    expect(routes.login()).toBe('/login');
    expect(routes.home()).toBe('/');
    expect(routes.solutions()).toBe('/solutions');
    expect(routes.instances('sol-1')).toBe('/solutions/sol-1/instances');
    expect(routes.instanceCategory('sol-1', 'inst-1', 'designer')).toBe(
      '/solutions/sol-1/instances/inst-1/designer',
    );
    expect(routes.explore('sol-1', 'inst-1')).toBe(
      '/solutions/sol-1/instances/inst-1/monitoring/explore',
    );
    expect(routes.dashboard('sol-1', 'inst-1', 'dash-1')).toBe(
      '/solutions/sol-1/instances/inst-1/monitoring/dashboards/dash-1',
    );
    expect(routes.targets()).toBe('/targets');
    expect(routes.admin()).toBe('/admin/roles');
    expect(routes.admin('users')).toBe('/admin/users');
  });
});
